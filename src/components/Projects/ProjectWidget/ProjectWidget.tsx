import * as React from 'react';
import Avatar from 'react-avatar';
import { Link } from 'react-router-dom';

import { PropertyType } from 'models/Filters';
import { Project, UserAccess } from 'models/Project';
import User from 'models/User';

import Draggable from '../../Draggable/Draggable';
import SharePopup from '../../SharePopup/SharePopup';
import routes from '../../../routes';
import combined from './images/combined.svg';
import styles from './ProjectWidget.module.css';
import tstyles from '../../TagBlock/TagBlock.module.css';

interface ILocalProps {
  project: Project;
}

interface ILocalState {
  showModal: boolean;
}

export default class ProjectWidget extends React.Component<ILocalProps, ILocalState> {
  public constructor(props: ILocalProps) {
    super(props);

    this.state = {
      showModal: false
    };

    this.showCollaborators = this.showCollaborators.bind(this);
    this.handleCloseModal = this.handleCloseModal.bind(this);
  }

  public render() {
    const project = this.props.project;
    const showCollaboratorsAvatars = project.collaborators.size > 1;
    const moreThanMaxCollaborators = project.collaborators.size - 3;

    return (
      <div>
        <SharePopup
          projectId={project.id}
          projectName={project.name}
          showModal={this.state.showModal}
          onRequestClose={this.handleCloseModal}
          collaborators={new Map<User, UserAccess>(project.collaborators)}
        />
        <Link className={styles.project_link} to={routes.expirementRuns.getRedirectPath({ projectId: project.id })}>
          <div className={styles.project_widget}>
            <div className={styles.title_block}>
              <div className={styles.title}>{project.name}</div>
              <div className={styles.description}>{project.description}</div>
            </div>
            <ul className={tstyles.tags}>
              {this.props.project.tags &&
                this.props.project.tags.map((tag: string, i: number) => {
                  return (
                    <Draggable
                      key={i}
                      type="filter"
                      data={{ type: PropertyType.STRING, name: 'Tag', value: tag }}
                      additionalClassName={styles.tag}
                    >
                      <span className={styles.tag_text}>{tag}</span>
                    </Draggable>
                  );
                })}
            </ul>
            <div className={styles.metrics_block} />
            <div className={styles.author_block}>
              <div className={styles.author_name}>
                <div>{project.Author.getNameOrEmail()}</div>
                <div className={styles.author_status}>Owner</div>
              </div>
              {/* // we may use mapProjectAuthors() function from ProjectDataService.ts 
            to map project Ids to owner once backend supports author field */}
              <Avatar
                name={project.Author.getNameOrEmail()}
                round={true}
                size="36"
                textSizeRatio={36 / 16}
                className={styles.author_avatar}
                src={project.Author.picture ? project.Author.picture : ''}
              />
            </div>
            <div className={styles.model_count_block}>
              <span className={styles.model_counter}>{12}</span>
              <span>model</span>
            </div>
            <div className={styles.collaborators}>
              <button
                className={styles.collaborate_button}
                style={{ paddingRight: showCollaboratorsAvatars ? 8 : 0 }}
                onClick={this.showCollaborators}
              >
                {showCollaboratorsAvatars ? '' : <img src={combined} className={styles.combined_icon} />}
                <span style={{ marginLeft: showCollaboratorsAvatars ? 14 : 4 }}>Collaborators</span>
                {showCollaboratorsAvatars ? (
                  <span>
                    {Array.from(project.collaborators.keys()).map((user: User, index: number) => {
                      return index < 3 ? (
                        <Avatar
                          key={index}
                          name={user.getNameOrEmail()}
                          round={true}
                          size="24"
                          textSizeRatio={24 / 11}
                          className={styles.collaborator_avatar}
                          src={user.picture ? user.picture : ''}
                        />
                      ) : (
                        ''
                      );
                    })}
                    {project.collaborators.size > 3 ? (
                      <Avatar
                        name={`+ ${moreThanMaxCollaborators}`}
                        round={true}
                        size="24"
                        textSizeRatio={24 / 11}
                        className={styles.collaborator_avatar}
                        color="#EDEDED"
                        fgColor="#666666"
                      />
                    ) : (
                      ''
                    )}
                  </span>
                ) : (
                  ''
                )}
              </button>
            </div>
            <div className={styles.created_date_block}>
              <div className={styles.created_date}>Created: {project.dateCreated.toLocaleDateString()}</div>
              <div>Updated: {project.dateUpdated.toLocaleDateString()}</div>
            </div>
          </div>
        </Link>
      </div>
    );
  }

  private showCollaborators(event: React.SyntheticEvent<HTMLButtonElement>) {
    event.preventDefault();
    this.setState({ showModal: true });
  }

  private handleCloseModal() {
    this.setState({ showModal: false });
  }
}
